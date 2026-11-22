using System.Windows;

namespace AirTether.Views;

public partial class CustomTextWindow : Window
{
    public string ResultText { get; private set; } = "";

    public CustomTextWindow(string currentText)
    {
        InitializeComponent();
        ResultText = currentText;
        TbCustomText.Text = currentText;
        Loaded += (_, _) =>
        {
            TbCustomText.Focus();
            TbCustomText.SelectAll();
        };
    }

    private void BtnReset_Click(object sender, RoutedEventArgs e)
    {
        ResultText = "";
        DialogResult = true;
        Close();
    }

    private void BtnCancel_Click(object sender, RoutedEventArgs e)
    {
        DialogResult = false;
        Close();
    }

    private void BtnSave_Click(object sender, RoutedEventArgs e)
    {
        ResultText = TbCustomText.Text.Trim();
        DialogResult = true;
        Close();
    }
}
